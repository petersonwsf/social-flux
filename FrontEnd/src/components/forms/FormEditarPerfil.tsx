import { Form, Formik } from 'formik'
import * as Yup from 'yup'
import { FormField } from './FormField'
import type { EditarPerfilPayload, Usuario } from '../../types/api'

const validationSchema = Yup.object({
    id: Yup.string().required(),
    username: Yup.string().required('Campo obrigatório'),
    nome: Yup.string().required('Campo obrigatório'),
    biografia: Yup.string().max(500, 'A biografia pode ter no máximo 500 caracteres').defined(),
})

interface FormEditarPerfilProps {
    user: Usuario
    onSubmit: (values: EditarPerfilPayload) => Promise<void>
}

function FormEditarPerfil({ user, onSubmit }: FormEditarPerfilProps) {
    const initialValues: EditarPerfilPayload = {
        id: user.id,
        username: user.username,
        nome: user.nome,
        biografia: user.biografia ?? '',
    }

    return (
        <Formik initialValues={initialValues} validationSchema={validationSchema} onSubmit={onSubmit}>
            {({ isSubmitting }) => (
                <Form noValidate>
                    <FormField name="username" label="Username" />
                    <FormField name="nome" label="Nome" />
                    <FormField name="biografia" label="Biografia" as="textarea" height={150} emoji />
                    <button type="submit" className="btn btn-primary mt-4" disabled={isSubmitting}>
                        Salvar alterações
                    </button>
                </Form>
            )}
        </Formik>
    )
}

export default FormEditarPerfil
